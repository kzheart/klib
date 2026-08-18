# Third-party notices

## TabooLib Kether Java core

The dependency-free parser primitives in `klib-script` contain code copied or
adapted from TabooLib's Java Kether core:

All 30 Java source types present in the fixed upstream Kether core directory
are represented by Java implementations in klib's `kether/core` package.

- Project: TabooLib — <https://github.com/TabooLib/taboolib>
- Fixed revision: `c27e822fb34eebd7433a94efbfac0a26943cccd6`
- Upstream paths:
  - `module/minecraft/minecraft-kether/src/main/java/taboolib/library/kether/*.java`
  - `module/minecraft/minecraft-kether/src/main/kotlin/taboolib/module/kether/action/ActionGet.kt`
  - `module/minecraft/minecraft-kether/src/main/kotlin/taboolib/module/kether/action/ActionLiteral.kt`
- klib paths:
  - `klib-script/src/main/java/me/kzheart/klib/script/kether/core/`
  - `klib-script/src/main/resources/META-INF/LICENSE-TabooLib-Kether.txt`
- Changes: package relocation; removal of JetBrains annotations, Guava,
  `Multimap`, and Kotlin upper-layer references; JDK-only coercion and parser
  combinators replacing Coerce/DataFixerUpper; Java replacements for the two
  Kotlin actions referenced by `SimpleReader`; localized lexing exception
  replacement; defensive collection/content copies; and Java 8 compilation.
  `SimpleQuestService` and `SimpleQuestContext` are klib additions. Original
  author/Javadoc and implementation comments are retained in the adapted files.

The two directly copied files were verified byte-for-byte against the fixed
revision before relocation (`AbstractStringReader.java` SHA-256
`1499195c34308a8f58ad19e7d60ab8afa17020a162a642e4c0bd82034d7b7d9f`;
`TokenBlock.java` SHA-256
`5e77e38727e9f07c8ef9c1db63882c8efa48442706c493532bd3769de31e1754`).

MIT License

Copyright (c) 2018 Bkm016

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## Runtime libraries

- SnakeYAML 1.33 — Apache License 2.0.
- Kyori Adventure API and MiniMessage 4.17.0 — MIT License.
- `maxminddb-golang` 2.2.0 — ISC License.
- MaxMind DB 测试数据库来自 [MaxMind-DB test-data](https://github.com/maxmind/MaxMind-DB/tree/main/test-data)，按 Apache-2.0 或 MIT 双许可使用。

These libraries remain subject to their upstream copyright and license terms.
