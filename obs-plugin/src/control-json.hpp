#pragma once

#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonValue>
#include <cmath>
#include <limits>
#include <optional>
#include <string>

// Control/discovery payloads are bounded by their transport before parsing.
// Use Qt's existing JSON parser so types, escaping, nesting and malformed
// packets cannot make a field consume digits or keys from another value.
inline QJsonValue control_json_value(const std::string &json, const std::string &key) {
  if (json.size() > 8192) return QJsonValue(QJsonValue::Undefined);
  const auto document = QJsonDocument::fromJson(QByteArray(json.data(), static_cast<int>(json.size())));
  if (!document.isObject()) return QJsonValue(QJsonValue::Undefined);
  return document.object().value(QString::fromStdString(key));
}

inline std::optional<std::string> json_string_value(const std::string &json, const std::string &key) {
  const auto value = control_json_value(json, key);
  if (!value.isString()) return std::nullopt;
  return value.toString().toUtf8().toStdString();
}

inline std::optional<int> json_int_value(const std::string &json, const std::string &key) {
  const auto value = control_json_value(json, key);
  if (!value.isDouble()) return std::nullopt;
  const double number = value.toDouble();
  if (!std::isfinite(number) || std::trunc(number) != number ||
      number < (std::numeric_limits<int>::min)() ||
      number > (std::numeric_limits<int>::max)()) return std::nullopt;
  return static_cast<int>(number);
}

inline std::optional<bool> json_bool_value(const std::string &json, const std::string &key) {
  const auto value = control_json_value(json, key);
  if (!value.isBool()) return std::nullopt;
  return value.toBool();
}
