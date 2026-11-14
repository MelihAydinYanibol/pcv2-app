# OkHttp / Okio ship their own consumer rules; kotlinx.serialization is only
# used through JsonElement (no generated serializers), so nothing to keep.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
