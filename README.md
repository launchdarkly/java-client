LaunchDarkly SDK for Java
=========================

[![Circle CI](https://circleci.com/gh/launchdarkly/java-client.svg?style=shield)](https://circleci.com/gh/launchdarkly/java-client)
[![Javadocs](http://javadoc.io/badge/com.launchdarkly/launchdarkly-client.svg)](http://javadoc.io/doc/com.launchdarkly/launchdarkly-client)
[![FOSSA Status](https://app.fossa.io/api/projects/git%2Bhttps%3A%2F%2Fgithub.com%2Flaunchdarkly%2Fjava-client.svg?type=shield)](https://app.fossa.io/projects/git%2Bhttps%3A%2F%2Fgithub.com%2Flaunchdarkly%2Fjava-client?ref=badge_shield)

Supported Java versions
-----------------------

This version of the LaunchDarkly SDK works with Java 7 and above.

Quick setup
-----------

0. Add the Java SDK to your project

        <dependency>
          <groupId>com.launchdarkly</groupId>
          <artifactId>launchdarkly-client</artifactId>
          <version>4.3.0</version>
        </dependency>

1. Import the LaunchDarkly package:

        import com.launchdarkly.client.*;

2. Create a new LDClient with your SDK key:

        LDClient ldClient = new LDClient("YOUR_SDK_KEY");

Your first feature flag
-----------------------

1. Create a new feature flag on your [dashboard](https://app.launchdarkly.com)
2. In your application code, use the feature's key to check wthether the flag is on for each user:

        LDUser user = new LDUser(username);
        boolean showFeature = ldClient.boolVariation("your.feature.key", user, false);
        if (showFeature) {
          // application code to show the feature 
        }
        else {
          // the code to run if the feature is off
        }


Logging
-------
The LaunchDarkly SDK uses [SLF4J](https://www.slf4j.org/). All loggers are namespaced under `com.launchdarkly`. For an example configuration check out the [hello-java](https://github.com/launchdarkly/hello-java) project.

Be aware of two considerations when enabling the DEBUG log level:
1. Debug-level logs can be very verbose. It is not recommended that you turn on debug logging in high-volume environments.
1. Potentially sensitive information is logged including LaunchDarkly users created by you in your usage of this SDK.

Learn more
----------

Check out our [documentation](http://docs.launchdarkly.com) for in-depth instructions on configuring and using LaunchDarkly. You can also head straight to the [complete reference guide for this SDK](http://docs.launchdarkly.com/docs/java-sdk-reference) or our [Javadocs](http://launchdarkly.github.io/java-client/).

Testing
-------

We run integration tests for all our SDKs using a centralized test harness. This approach gives us the ability to test for consistency across SDKs, as well as test networking behavior in a long-running application. These tests cover each method in the SDK, and verify that event sending, flag evaluation, stream reconnection, and other aspects of the SDK all behave correctly.


Contributing
------------

We encourage pull-requests and other contributions from the community. We've also published an [SDK contributor's guide](http://docs.launchdarkly.com/docs/sdk-contributors-guide) that provides a detailed explanation of how our SDKs work.

About LaunchDarkly
-----------

* LaunchDarkly is a continuous delivery platform that provides feature flags as a service and allows developers to iterate quickly and safely. We allow you to easily flag your features and manage them from the LaunchDarkly dashboard.  With LaunchDarkly, you can:
    * Roll out a new feature to a subset of your users (like a group of users who opt-in to a beta tester group), gathering feedback and bug reports from real-world use cases.
    * Gradually roll out a feature to an increasing percentage of users, and track the effect that the feature has on key metrics (for instance, how likely is a user to complete a purchase if they have feature A versus feature B?).
    * Turn off a feature that you realize is causing performance problems in production, without needing to re-deploy, or even restart the application with a changed configuration file.
    * Grant access to certain features based on user attributes, like payment plan (eg: users on the ‘gold’ plan get access to more features than users in the ‘silver’ plan). Disable parts of your application to facilitate maintenance, without taking everything offline.
* LaunchDarkly provides feature flag SDKs for
    * [Java](http://docs.launchdarkly.com/docs/java-sdk-reference "Java SDK")
    * [JavaScript](http://docs.launchdarkly.com/docs/js-sdk-reference "LaunchDarkly JavaScript SDK")
    * [PHP](http://docs.launchdarkly.com/docs/php-sdk-reference "LaunchDarkly PHP SDK")
    * [Python](http://docs.launchdarkly.com/docs/python-sdk-reference "LaunchDarkly Python SDK")
    * [Python Twisted](http://docs.launchdarkly.com/docs/python-twisted-sdk-reference "LaunchDarkly Python Twisted SDK")
    * [Go](http://docs.launchdarkly.com/docs/go-sdk-reference "LaunchDarkly Go SDK")
    * [Node.JS](http://docs.launchdarkly.com/docs/node-sdk-reference "LaunchDarkly Node SDK")
    * [.NET](http://docs.launchdarkly.com/docs/dotnet-sdk-reference "LaunchDarkly .Net SDK")
    * [Ruby](http://docs.launchdarkly.com/docs/ruby-sdk-reference "LaunchDarkly Ruby SDK")
    * [iOS](http://docs.launchdarkly.com/docs/ios-sdk-reference "LaunchDarkly iOS SDK")
    * [Android](http://docs.launchdarkly.com/docs/android-sdk-reference "LaunchDarkly Android SDK")
* Explore LaunchDarkly
    * [launchdarkly.com](http://www.launchdarkly.com/ "LaunchDarkly Main Website") for more information
    * [docs.launchdarkly.com](http://docs.launchdarkly.com/  "LaunchDarkly Documentation") for our documentation and SDKs
    * [apidocs.launchdarkly.com](http://apidocs.launchdarkly.com/  "LaunchDarkly API Documentation") for our API documentation
    * [blog.launchdarkly.com](http://blog.launchdarkly.com/  "LaunchDarkly Blog Documentation") for the latest product updates
    * [Feature Flagging Guide](https://github.com/launchdarkly/featureflags/  "Feature Flagging Guide") for best practices and strategies

