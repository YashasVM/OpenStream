#include "phone-ownership.hpp"
#include "contract_helpers.hpp"

#include <atomic>
#include <iostream>
#include <thread>
#include <vector>

using openstream_test::require;

int main() {
  PhoneOwnershipRegistry lease;
  require(!lease.acquire("", "source-a"), "empty phone accepted");
  require(lease.acquire("phone-a", "source-a"), "first phone rejected");
  require(lease.acquire("phone-b", "source-b"), "second phone blocked by unrelated source");
  require(lease.acquire("phone-a", "source-a"), "idempotent acquire rejected");
  require(!lease.acquire("phone-a", "source-b"), "same phone admitted to another source");
  lease.release("phone-a", "source-b");
  require(!lease.acquire("phone-a", "source-b"), "non-owner released the live phone");
  lease.release("phone-a", "source-a");
  require(lease.acquire("phone-a", "source-b"), "released phone cannot be acquired");
  lease.release("phone-a", "source-a");
  require(!lease.acquire("phone-a", "source-a"), "stale release changed ownership");
  lease.release("phone-a", "source-b");

  // All contenders hold their acquisition until every attempt has completed.
  // Exactly one source may start, even when scene loading starts them together.
  std::atomic<int> ready{0};
  std::atomic<bool> start{false};
  std::atomic<int> acquired{0};
  std::string owners[16];
  std::vector<std::thread> contenders;
  for (std::string &owner : owners) {
    owner = "source-" + std::to_string(&owner - owners);
    contenders.emplace_back([&, token = owner] {
      ++ready;
      while (!start.load()) std::this_thread::yield();
      if (lease.acquire("phone-race", token)) ++acquired;
    });
  }
  while (ready.load() != 16) std::this_thread::yield();
  start = true;
  for (auto &thread : contenders) thread.join();
  require(acquired.load() == 1, "concurrent starts admitted multiple cameras");
  for (const std::string &owner : owners) lease.release("phone-race", owner);
  require(lease.acquire("phone-race", "source-final"), "registry leaked after cleanup");
  lease.release("phone-race", "source-final");
  PhoneOwnershipRegistry bounded;
  std::vector<std::string> phones;
  phones.reserve(PhoneOwnershipRegistry::kCapacity);
  for (std::size_t index = 0; index < PhoneOwnershipRegistry::kCapacity; ++index) {
    phones.push_back("phone-capacity-" + std::to_string(index));
    require(bounded.acquire(phones.back(), "source-capacity"),
            "bounded registry rejected an in-capacity phone");
  }
  require(!bounded.acquire("phone-overflow", "source-overflow"),
          "phone ownership registry grew beyond its declared capacity");
  std::cout << "Phone ownership tests passed\n";
}
