# R8 is only used here to dex the compiled classes -- shrinking,
# optimization, and obfuscation are all off, so it never removes or renames
# anything.
-dontshrink
-dontoptimize
-dontobfuscate
-dontwarn **

# Attribute stripping (including annotations, needed for reflection-based
# APIs like @JavascriptInterface) is controlled separately from the three
# flags above and defaults to "strip everything" regardless of them.
-keepattributes *Annotation*
