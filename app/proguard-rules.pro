-keepattributes *Annotation*
-keepclassmembers class * extends androidx.room.RoomDatabase {
    abstract *;
}

# WorkManager: keep Worker subclasses (instantiated by class name via WorkerFactory)
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker
