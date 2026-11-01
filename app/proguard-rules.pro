# UniStore release rules

# WorkManager instantiates workers by class name from its own database, so the
# constructor must survive R8.
-keep class ru.updatehub.app.notifications.UpdateCheckWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Persisted update statuses are stored as enum names in JSON and restored with
# valueOf(), so keep enum constants readable after obfuscation.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}
