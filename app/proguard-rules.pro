-keep class com.simplexray.an.service.TProxyService {
    @kotlin.jvm.JvmStatic *;
}

-keep class com.simplexray.an.feature.subscriptions.model.Subscription { *; }
-keep class com.simplexray.an.feature.subscriptions.model.SubscriptionUsage { *; }
-keep class com.simplexray.an.feature.routing.model.RoutingSettings { *; }
-keep class com.simplexray.an.feature.routing.model.RoutingRule { *; }
-keep class com.simplexray.an.feature.routing.model.RoutingBlock { *; }
-keep class com.simplexray.an.feature.routing.model.RoutingServerRef { *; }
-keep enum com.simplexray.an.feature.routing.model.RouteTarget { *; }
-keep enum com.simplexray.an.feature.routing.model.RuleKind { *; }
-keep enum com.simplexray.an.feature.routing.model.DomainStrategy { *; }

-keep class com.simplexray.an.feature.servers.manual.model.ManualServerDraft { *; }
-keep class com.simplexray.an.feature.servers.manual.model.ManualValue { *; }
