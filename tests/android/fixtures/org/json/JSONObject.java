package org.json;

/** Only the captcha-requirement JSON consumed by the HTTP login fixture. */
public final class JSONObject {
  private final Boolean required;

  public JSONObject(String source) {
    String json = source.replaceAll("\\s", "");
    if (!json.equals("{\"isNeed\":true}") && !json.equals("{\"isNeed\":false}")) {
      throw new IllegalArgumentException("Invalid fixture JSON");
    }
    required = json.equals("{\"isNeed\":true}");
  }

  public Object opt(String name) { return name.equals("isNeed") ? required : null; }
}
