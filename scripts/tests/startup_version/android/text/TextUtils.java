package android.text;
public class TextUtils {
 public static boolean isEmpty(CharSequence s) { return s == null || s.length() == 0; }
 public static boolean equals(CharSequence a, CharSequence b) { return a == null ? b == null : a.toString().contentEquals(b); }
}
