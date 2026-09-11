package android.content;
import java.io.File;
import android.content.pm.*;
public abstract class Context {
 public abstract String getPackageName();
 public abstract PackageManager getPackageManager();
 public abstract ApplicationInfo getApplicationInfo();
 public abstract File getFilesDir();
}
