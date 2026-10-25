# 第三方许可

- MobileAgent-Android：MIT，Copyright (c) 2026 Yixiao Wang，原LICENSE完整保留。
- Kotlin stdlib、kotlinx-coroutines、kotlinx-serialization、JetBrains annotations：Apache-2.0，https://github.com/JetBrains/kotlin / https://github.com/Kotlin/kotlinx.coroutines / https://github.com/Kotlin/kotlinx.serialization / https://github.com/JetBrains/java-annotations。
- OkHttp 4.12.0、Okio 3.6.0：Apache-2.0，https://github.com/square/okhttp / https://github.com/square/okio。
- Gradle wrapper / AndroidX测试组件：Apache-2.0，https://github.com/gradle/gradle / https://android.googlesource.com/platform/frameworks/support/。
- JUnit4（测试依赖，未复制运行时JAR）：EPL-1.0，https://github.com/junit-team/junit4/blob/main/LICENSE-junit.txt。
- Qwen3-VL公开模型的model card声明Apache-2.0；本交付不包含模型权重，model card和权重SHA256/LFS比对证据保留于tests/evidence。
- llama.cpp测试工具：MIT，原文llama.cpp-MIT.txt；不分发运行时exe/dll。

Apache-2.0完整文本见Apache-2.0.txt。runtime-artifacts.json列出实际附带依赖的文件和SHA256；JAR内原始许可/NOTICE保留，并将发现的条目原样提取到dependency-notices/。未修改依赖二进制，不声明拥有上游代码版权。Gradle构建插件/SDK/JDK为开发机外部工具，不打入交付。
