package ani.kiwami.connections.crashlytics

class CrashlyticsFactory {
    companion object {
        fun createCrashlytics(): CrashlyticsInterface {
            return CrashlyticsStub()
        }
    }
}