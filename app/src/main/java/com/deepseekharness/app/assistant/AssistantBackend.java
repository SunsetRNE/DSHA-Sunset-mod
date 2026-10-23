package com.deepseekharness.app.assistant;

import java.io.IOException;
import org.json.JSONObject;

/** Mutating calls are sent once. A transport exception leaves their result unknown. */
public interface AssistantBackend {
  JSONObject call(String method, JSONObject args) throws IOException;
  boolean current();
  void returnConversation(AssistantBinding binding) throws IOException;
}
