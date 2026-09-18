#include "control-json.hpp"
#include "contract_helpers.hpp"
#include <cstdlib>
#include <iostream>

using openstream_test::require;
int main() {
  require(json_int_value(R"({"port":9000,"name":"Phone123"})", "port") == 9000);
  require(!json_int_value(R"({"port":"bad","name":"Phone123"})", "port"));
  require(json_int_value(R"({"port":-1})", "port") == -1);
  require(!json_int_value(R"({"port":9000.5})", "port"));
  require(!json_int_value(R"({"port":2147483648})", "port"));
  require(!json_int_value(R"({"nested":{"port":9000}})", "port"));
  require(!json_string_value(R"({"name":null,"next":"wrong"})", "name"));
  require(json_string_value(R"({"name":"Phone \"A\" \u00d7"})", "name") == "Phone \"A\" ×");
  require(json_bool_value(R"({"busy":false})", "busy") == false);
  require(!json_bool_value(R"({"busy":"false"})", "busy"));
  require(!json_bool_value(R"({"busy":trueBAD})", "busy"));
  require(!json_int_value(R"({"port":9000} garbage)", "port"));
  require(!json_int_value(std::string(8193, ' '), "port"));
  std::cout << "Control JSON tests passed\n";
}
