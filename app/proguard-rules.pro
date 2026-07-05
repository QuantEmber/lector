# Lector release shrinking rules.

# PdfBox-Android references an optional JPEG-2000 decoder (com.gemalto.jp2) that
# we don't bundle; the reference is only hit for JPXDecode-filtered images. Tell
# R8 not to warn/fail on the absent class.
-dontwarn com.gemalto.jp2.JP2Decoder

# Compose and system TTS are covered by AGP's default keep rules.
