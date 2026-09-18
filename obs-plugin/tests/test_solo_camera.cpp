#include "solo-camera-lease.hpp"
#include "contract_helpers.hpp"

#include <atomic>
#include <iostream>
#include <thread>
#include <vector>

using openstream_test::require;

int main() {
  SoloCameraLease lease;
  int first = 0;
  int second = 0;
  require(!lease.acquire(nullptr), "null owner accepted");
  require(lease.acquire(&first), "first camera rejected");
  require(lease.acquire(&first), "idempotent acquire rejected");
  require(!lease.acquire(&second), "second camera started");
  lease.release(&second);
  require(!lease.acquire(&second), "non-owner released the live camera");
  lease.release(&first);
  require(lease.acquire(&second), "explicit retry after stop rejected");
  lease.release(&first);
  require(!lease.acquire(&first), "stale stop released the new camera");
  lease.release(&second);

  // All contenders hold their acquisition until every attempt has completed.
  // Exactly one source may start, even when scene loading starts them together.
  std::atomic<int> ready{0};
  std::atomic<bool> start{false};
  std::atomic<int> acquired{0};
  int owners[16]{};
  std::vector<std::thread> contenders;
  for (int &owner : owners) {
    contenders.emplace_back([&, token = &owner] {
      ++ready;
      while (!start.load()) std::this_thread::yield();
      if (lease.acquire(token)) ++acquired;
    });
  }
  while (ready.load() != 16) std::this_thread::yield();
  start = true;
  for (auto &thread : contenders) thread.join();
  require(acquired.load() == 1, "concurrent starts admitted multiple cameras");
  for (int &owner : owners) lease.release(&owner);
  require(lease.acquire(&first), "lease leaked after cleanup");
  std::cout << "Solo camera ownership tests passed\n";
}
