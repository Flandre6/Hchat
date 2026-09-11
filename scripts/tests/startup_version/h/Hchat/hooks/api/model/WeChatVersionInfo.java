package h.Hchat.hooks.api.model;
public class WeChatVersionInfo {
 public final String packageName, versionName, clientVersion, tinkerId, patchId, classLoaderHash, cacheKey;
 public final long versionCode, sourceLastModified;
 public WeChatVersionInfo(String p, String v, long c, String client, String tinker, String patch, long modified, String loader, String key) {
  packageName=p; versionName=v; versionCode=c; clientVersion=client; tinkerId=tinker; patchId=patch; sourceLastModified=modified; classLoaderHash=loader; cacheKey=key;
 }
 public boolean hasTinkerPatch() { return !tinkerId.isEmpty() || !patchId.isEmpty(); }
}
