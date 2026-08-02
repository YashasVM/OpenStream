#include <nlohmann/json.hpp>

#include <string>

static_assert(_MSVC_LANG >= 202002L, "OpenStream V4 requires C++20");

int main() {
  const auto state = nlohmann::json::parse(R"({"protocol":4,"status":"ready"})");
  return state.at("protocol") == 4 && state.at("status") == std::string{"ready"} ? 0 : 1;
}
