"""Run the production NativeSender against a fake libsrt to prove socket ownership.

IRL cancels a connect attempt and starts a replacement on another thread. The
cancelled attempt can return from DNS or srt_connect() after the replacement
published its socket; its cleanup must only ever touch the socket it created.
"""
import shutil
import subprocess

import pytest

from _helpers import read_text


HARNESS_PRELUDE = r"""
#include <algorithm>
#include <array>
#include <atomic>
#include <cassert>
#include <charconv>
#include <chrono>
#include <condition_variable>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <functional>
#include <map>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <utility>
#include <vector>
#include <arpa/inet.h>
#include <netdb.h>
#include <sys/socket.h>
#include <srt/srt.h>
#define OPENSTREAM_HAVE_LIBSRT 1
typedef int64_t jlong;
enum { ANDROID_LOG_INFO = 4, ANDROID_LOG_WARN = 5, ANDROID_LOG_ERROR = 6 };
int __android_log_print(int, const char *, const char *, ...) { return 0; }
constexpr const char *kTag = "test";
constexpr int kMinSrtLatencyMs = 80;
constexpr int kMaxSrtLatencyMs = 200;
constexpr int kListenerAcceptPollMs = 100;
void logInfo(const char *) {}
void logError(const char *) {}

// ---- Fake libsrt: socket ids are handles; any call on a closed id is a
// use-after-close. Hooks let a test hold a connect attempt inside DNS or
// srt_connect() while other threads cancel and replace it.
std::mutex fakeMutex;
std::condition_variable fakeChanged;
SRTSOCKET nextSocket = 100;
std::map<SRTSOCKET, int> closes;
std::map<SRTSOCKET, int> connectCalls;
std::vector<SRTSOCKET> sentOn;
std::vector<SRTSOCKET> statsOn;
int useAfterClose = 0;
int created = 0;
int rejectReason = SRT_REJ_BADSECRET;
int addressCount = 1;
std::function<int(SRTSOCKET)> connectHook = [](SRTSOCKET) { return 0; };
std::function<void()> dnsHook = [] {};

void touch(SRTSOCKET socket) {
  std::lock_guard<std::mutex> lock(fakeMutex);
  if (closes[socket] > 0) ++useAfterClose;
}
bool isClosed(SRTSOCKET socket) {
  std::lock_guard<std::mutex> lock(fakeMutex);
  return closes[socket] > 0;
}
void waitUntil(const std::function<bool()> &condition) {
  std::unique_lock<std::mutex> lock(fakeMutex);
  assert(fakeChanged.wait_for(lock, std::chrono::seconds(5), condition));
}

extern "C" {
int srt_startup(void) { return 0; }
int srt_cleanup(void) { return 0; }
SRTSOCKET srt_create_socket(void) {
  std::lock_guard<std::mutex> lock(fakeMutex);
  ++created;
  return nextSocket++;
}
int srt_setsockopt(SRTSOCKET u, int, SRT_SOCKOPT, const void *, int) { touch(u); return 0; }
int srt_connect(SRTSOCKET u, const struct sockaddr *, int) {
  touch(u);
  std::function<int(SRTSOCKET)> hook;
  {
    std::lock_guard<std::mutex> lock(fakeMutex);
    ++connectCalls[u];
    hook = connectHook;
  }
  fakeChanged.notify_all();
  return hook(u);
}
int srt_getrejectreason(SRTSOCKET u) { touch(u); return rejectReason; }
int srt_close(SRTSOCKET u) {
  {
    std::lock_guard<std::mutex> lock(fakeMutex);
    ++closes[u];
  }
  fakeChanged.notify_all();
  return 0;
}
const char *srt_getlasterror_str(void) { return "fake"; }
int srt_getlasterror(int *) { return SRT_EASYNCRCV; }
int srt_bistats(SRTSOCKET u, SRT_TRACEBSTATS *, int, int) {
  touch(u);
  std::lock_guard<std::mutex> lock(fakeMutex);
  statsOn.push_back(u);
  return 0;
}
int srt_sendmsg(SRTSOCKET u, const char *, int len, int, int) {
  touch(u);
  {
    std::lock_guard<std::mutex> lock(fakeMutex);
    sentOn.push_back(u);
  }
  fakeChanged.notify_all();
  return len;
}
int srt_epoll_create(void) { return SRT_ERROR; }
int srt_epoll_add_usock(int, SRTSOCKET, const int *) { return SRT_ERROR; }
int srt_epoll_uwait(int, SRT_EPOLL_EVENT *, int, int64_t) { return SRT_ERROR; }
int srt_epoll_remove_usock(int, SRTSOCKET) { return 0; }
int srt_epoll_release(int) { return 0; }
int srt_bind(SRTSOCKET, const struct sockaddr *, int) { return SRT_ERROR; }
int srt_listen(SRTSOCKET, int) { return SRT_ERROR; }
SRTSOCKET srt_accept(SRTSOCKET, struct sockaddr *, int *) { return SRT_INVALID_SOCK; }
}

int fake_getaddrinfo(const char *, const char *, const addrinfo *, addrinfo **out) {
  std::function<void()> hook;
  int count = 0;
  {
    std::lock_guard<std::mutex> lock(fakeMutex);
    hook = dnsHook;
    count = addressCount;
  }
  hook();
  addrinfo *head = nullptr;
  for (int i = 0; i < count; ++i) {
    auto *address = new sockaddr_in{};
    address->sin_family = AF_INET;
    auto *entry = new addrinfo{};
    entry->ai_addr = reinterpret_cast<sockaddr *>(address);
    entry->ai_addrlen = sizeof(sockaddr_in);
    entry->ai_next = head;
    head = entry;
  }
  *out = head;
  return 0;
}
void fake_freeaddrinfo(addrinfo *entry) {
  while (entry != nullptr) {
    addrinfo *next = entry->ai_next;
    delete reinterpret_cast<sockaddr_in *>(entry->ai_addr);
    delete entry;
    entry = next;
  }
}
#define getaddrinfo fake_getaddrinfo
#define freeaddrinfo fake_freeaddrinfo
"""

HARNESS_MAIN = r"""
const std::string kIrl = "srt://127.0.0.1:9000?irl=1";
const std::string kObs = "srt://127.0.0.1:9000";

void resetHooks() {
  std::lock_guard<std::mutex> lock(fakeMutex);
  addressCount = 1;
  connectHook = [](SRTSOCKET) { return 0; };
  dnsHook = [] {};
}

int main() {
  NativeSender sender;
  sender.initializeRuntime();

  // 1. Network switch: attempt A is cancelled while blocked in srt_connect(),
  // replacement B publishes its socket, then A's srt_connect() fails late.
  bool releaseA = false;
  SRTSOCKET socketA = SRT_INVALID_SOCK;
  connectHook = [&](SRTSOCKET u) {
    std::unique_lock<std::mutex> lock(fakeMutex);
    socketA = u;
    fakeChanged.notify_all();
    fakeChanged.wait(lock, [&] { return releaseA; });
    return SRT_ERROR;
  };
  sender.advanceLifecycleGeneration(1);
  bool resultA = true;
  std::thread attemptA([&] { resultA = sender.connect(kIrl, 1); });
  waitUntil([&] { return socketA != SRT_INVALID_SOCK; });
  sender.advanceLifecycleGeneration(2);  // client A's disconnect()
  sender.disconnect();
  assert(isClosed(socketA));
  {
    std::lock_guard<std::mutex> lock(fakeMutex);
    connectHook = [](SRTSOCKET) { return 0; };
  }
  sender.advanceLifecycleGeneration(3);  // client B's beginSession()
  assert(sender.connect(kIrl, 3));
  const SRTSOCKET socketB = nextSocket - 1;
  assert(socketB != socketA);
  {
    std::lock_guard<std::mutex> lock(fakeMutex);
    releaseA = true;
  }
  fakeChanged.notify_all();
  attemptA.join();
  assert(!resultA);
  assert(closes[socketA] == 1);
  assert(closes[socketB] == 0);
  assert(sender.connectError().rfind("AUTH:", 0) != 0);  // A's reject reason is not B's error
  assert(sender.send(std::vector<uint8_t>(188, 0x47)));
  waitUntil([&] { return !sentOn.empty(); });
  assert(sentOn.back() == socketB);
  sender.publisherStats();
  assert(statsOn.back() == socketB);
  sender.disconnect();
  assert(closes[socketB] == 1);

  // 2. Cancelling an in-flight srt_connect() still aborts it (real libsrt
  // returns once the socket is closed) and the socket is closed exactly once.
  SRTSOCKET socketC = SRT_INVALID_SOCK;
  connectHook = [&](SRTSOCKET u) {
    std::unique_lock<std::mutex> lock(fakeMutex);
    socketC = u;
    fakeChanged.notify_all();
    fakeChanged.wait(lock, [&] { return closes[u] > 0; });
    return SRT_ERROR;
  };
  sender.advanceLifecycleGeneration(4);
  bool resultC = true;
  std::thread attemptC([&] { resultC = sender.connect(kIrl, 4); });
  waitUntil([&] { return socketC != SRT_INVALID_SOCK; });
  sender.advanceLifecycleGeneration(5);
  sender.disconnect();
  attemptC.join();
  assert(!resultC);
  assert(closes[socketC] == 1);
  resetHooks();

  // 3. Cancelled during DNS with several addresses: no address is tried on
  // the closed socket, and the late cleanup closes nothing else.
  addressCount = 3;
  bool inDns = false, dnsCancelled = false;
  dnsHook = [&] {
    std::unique_lock<std::mutex> lock(fakeMutex);
    inDns = true;
    fakeChanged.notify_all();
    fakeChanged.wait(lock, [&] { return dnsCancelled; });
  };
  sender.advanceLifecycleGeneration(6);
  bool resultD = true;
  std::thread attemptD([&] { resultD = sender.connect(kIrl, 6); });
  waitUntil([&] { return inDns; });
  const SRTSOCKET socketD = nextSocket - 1;
  sender.advanceLifecycleGeneration(7);
  sender.disconnect();
  {
    std::lock_guard<std::mutex> lock(fakeMutex);
    dnsCancelled = true;
  }
  fakeChanged.notify_all();
  attemptD.join();
  assert(!resultD);
  assert(connectCalls[socketD] == 0);
  assert(closes[socketD] == 1);
  resetHooks();

  // 4. An attempt that still owns its socket tries every address, closes its
  // own socket once, and reports its IRL reject reason.
  addressCount = 2;
  connectHook = [](SRTSOCKET) { return SRT_ERROR; };
  sender.advanceLifecycleGeneration(8);
  assert(!sender.connect(kIrl, 8));
  const SRTSOCKET socketE = nextSocket - 1;
  assert(connectCalls[socketE] == 2);
  assert(closes[socketE] == 1);
  assert(sender.connectError().rfind("AUTH:", 0) == 0);
  const size_t statsBefore = statsOn.size();
  sender.publisherStats();
  assert(statsOn.size() == statsBefore);  // no socket left published
  resetHooks();

  // 5. Legacy OBS caller: reconnecting within one lifecycle still replaces
  // (and closes) the previous socket.
  sender.advanceLifecycleGeneration(9);
  assert(sender.connect(kObs, 9));
  const SRTSOCKET socketF = nextSocket - 1;
  assert(sender.connect(kObs, 9));
  const SRTSOCKET socketG = nextSocket - 1;
  assert(closes[socketF] == 1 && closes[socketG] == 0);

  // 6. A connect for a superseded lifecycle does nothing at all.
  const int createdBefore = created;
  assert(!sender.connect(kObs, 8));
  assert(created == createdBefore);
  assert(closes[socketG] == 0);
  sender.disconnect();
  assert(closes[socketG] == 1);

  assert(useAfterClose == 0);
  for (const auto &[socket, count] : closes) assert(count <= 1);
  return 0;
}
"""


def test_cancelled_native_connect_only_closes_its_own_socket(tmp_path):
    compiler = shutil.which("g++") or shutil.which("clang++")
    if compiler is None:
        pytest.skip("native ownership test requires a C++ compiler")
    source = read_text("android/app/src/main/cpp/openstream_srt.cpp")
    sender = source[source.index("struct SrtUrl") : source.index("struct StreamState")]
    include = "android/app/src/main/cpp/third_party/srt/include"
    cpp = tmp_path / "srt_ownership_test.cpp"
    executable = tmp_path / "srt_ownership_test"
    cpp.write_text(HARNESS_PRELUDE + sender + HARNESS_MAIN)
    subprocess.run(
        [compiler, "-std=c++20", "-O1", "-g", "-pthread", "-fsanitize=address,undefined",
         "-I", include, str(cpp), "-o", str(executable)],
        check=True,
    )
    subprocess.run([str(executable)], check=True, timeout=30)
