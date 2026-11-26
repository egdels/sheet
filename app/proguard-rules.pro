# Copyright (C) 2026 Christian Kierdorf
# SPDX-License-Identifier: GPL-3.0-or-later
-keepattributes *Annotation*, InnerClasses
-dontwarn org.slf4j.**

-keepclasseswithmembernames class de.schliweb.sheet.core.ncnn.NcnnNet { native <methods>; }
