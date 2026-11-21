# Public API of the SDK is kept; internals may be obfuscated by the integrator's R8 run.
-keep public class com.airline.mrz.PassportScanner { public *; }
-keep public class com.airline.mrz.ScanConfig { *; }
-keep public class com.airline.mrz.ScanResult { *; }
-keep public class com.airline.mrz.ScanResult$* { *; }
-keep public class com.airline.mrz.ScanState { *; }
-keep public class com.airline.mrz.ScanState$* { *; }
-keep public class com.airline.mrz.ScanSession { public *; }
-keep public class com.airline.mrz.contract.** { *; }
-keep public class com.airline.mrz.diagnostics.** { *; }
-keep public class com.airline.mrz.core.model.** { *; }
-keep public class com.airline.mrz.core.result.** { *; }
# Results are passed between activities as java.io.Serializable.
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
