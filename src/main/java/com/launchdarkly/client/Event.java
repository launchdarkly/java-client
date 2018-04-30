package com.launchdarkly.client;

import com.google.gson.JsonElement;

/**
 * Base class for all analytics events that are generated by the client. Also defines all of its own subclasses.
 */
public class Event {
  final long creationDate;
  final LDUser user;

  public Event(long creationDate, LDUser user) {
    this.creationDate = creationDate;
    this.user = user;
  }
  
  public static final class Custom extends Event {
    final String key;
    final JsonElement data;

    public Custom(long timestamp, String key, LDUser user, JsonElement data) {
      super(timestamp, user);
      this.key = key;
      this.data = data;
    }
  }

  public static final class Identify extends Event {
    public Identify(long timestamp, LDUser user) {
      super(timestamp, user);
    }
  }

  public static final class Index extends Event {
    public Index(long timestamp, LDUser user) {
      super(timestamp, user);
    }
  }
  
  public static final class FeatureRequest extends Event {
    final String key;
    final Integer variation;
    final JsonElement value;
    final JsonElement defaultVal;
    final Integer version;
    final String prereqOf;
    final boolean trackEvents;
    final Long debugEventsUntilDate;
    final boolean debug;
    
    public FeatureRequest(long timestamp, String key, LDUser user, Integer version, Integer variation, JsonElement value,
        JsonElement defaultVal, String prereqOf, boolean trackEvents, Long debugEventsUntilDate, boolean debug) {
      super(timestamp, user);
      this.key = key;
      this.version = version;
      this.variation = variation;
      this.value = value;
      this.defaultVal = defaultVal;
      this.prereqOf = prereqOf;
      this.trackEvents = trackEvents;
      this.debugEventsUntilDate = debugEventsUntilDate;
      this.debug = debug;
    }
  }

}