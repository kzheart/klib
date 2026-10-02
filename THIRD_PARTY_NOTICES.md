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

## Structured Kether actions

`StructuredScriptActions` implements nested text actions, case branches and JEXL
expressions using the behavior of the following MIT-licensed TabooLib sources:

- Fixed revision: `0e3a911fc55624075b5c9abd4368cb5b063b022b`
- Base path: `module/minecraft/minecraft-kether/src/main/kotlin/taboolib/module/kether/`
- Files: `action/game/Actions.kt`, `action/transform/ActionWhen.kt`,
  `action/transform/CheckType.kt`, `action/transform/ActionJexl3.kt`, `KetherHelper.kt`.
- Copyright (c) 2018 Bkm016; MIT terms are reproduced above and in the JAR's
  `META-INF/LICENSE-TabooLib-Kether.txt`.
- Changes: independent Java 8 implementation, explicit ScriptContext services,
  strict statement parsing, propagated failures, executor-bound asynchronous
  continuations, and no dependency on the TabooLib runtime.

## Native value actions and immediate templates

`NativeValueActions` and `ScriptTemplates` adapt semantics from the same fixed
TabooLib revision `0e3a911fc55624075b5c9abd4368cb5b063b022b`:

- `module/minecraft/minecraft-kether/src/main/kotlin/taboolib/module/kether/`:
  `action/ActionSet.kt`, `action/transform/ActionRandom.kt`,
  `action/transform/ActionMath.kt`, `action/transform/Actions.kt`,
  `action/game/Actions.kt`, `KetherMath.kt`, `KetherFunction.kt`.
- `common-util/src/main/kotlin/taboolib/common/util/VariableReader.kt` and `Random.kt`.
- `common-platform-api/src/main/kotlin/taboolib/common/util/CommandSender.kt`.
- Copyright (c) 2018 Bkm016; the MIT terms are reproduced above and in
  `META-INF/LICENSE-TabooLib-Kether.txt`.
- Changes: Java 8 translation; explicit host services; original Klib bare-set
  syntax retained; executor-bound asynchronous continuations; independent
  frame/context bridge; no TabooLib or Kotlin runtime dependency.

The numeric sanitising/coercion algorithm also derives from
`common-legacy-api/src/main/java/taboolib/common5/Coerce.java` at the same
revision. Copyright (c) SpongePowered <https://www.spongepowered.org> and
contributors, MIT; the full notice is included in
`META-INF/LICENSE-Sponge-Coerce.txt`.

## Packet sidebars

`klib-compat`'s `me.kzheart.klib.compat.sidebar` package and the
`V1_12SidebarBridge`, `V1_20SidebarBridge`, `V1_21SidebarBridge` and
`V26SidebarBridge` classes in the `klib-compat-v*` modules adapt the per-player
packet scoreboard of the same fixed TabooLib revision
`0e3a911fc55624075b5c9abd4368cb5b063b022b`:

- `module/bukkit-nms/bukkit-nms-stable/src/main/kotlin/taboolib/module/nms/`:
  `NMSScoreboard.kt`, `NMSScoreboardImpl.kt`, `NMSScoreboardImpl26.kt` and
  `type/PlayerScoreboard.kt`.
- Copyright (c) 2018 Bkm016; the MIT terms are reproduced above and in each
  affected JAR's `META-INF/LICENSE-TabooLib-Scoreboard.txt`.
- Changes: independent Java 8 implementation without the TabooLib or Kotlin
  runtime; reflection resolved by member signatures instead of TabooLib's NMS
  proxy and remapper; scope-bound lifecycle with quit cleanup; line diffs keyed
  by score index; random per-instance objective, team and holder names; score
  display text with a blank number format from 1.20.4 instead of line teams;
  the 1.12 removal packet targets the line holder; team prefix, suffix, colour
  and JSON text features are not included.

## Runtime libraries

- Apache Commons JEXL 3.2.1 and Commons Logging 1.2 — Apache License 2.0.
- SnakeYAML 1.33 — Apache License 2.0.
- Kyori Adventure API and MiniMessage 4.17.0 — MIT License.
- `maxminddb-golang` 2.2.0 — ISC License.
- MaxMind DB 测试数据库来自 [MaxMind-DB test-data](https://github.com/maxmind/MaxMind-DB/tree/main/test-data)，按 Apache-2.0 或 MIT 双许可使用。

These libraries remain subject to their upstream copyright and license terms.

## Native checks and host queries

`NativeCheckActions` adapts the same fixed TabooLib revision and MIT terms above:

- `module/minecraft/minecraft-kether/src/main/kotlin/taboolib/module/kether/`:
  `action/transform/Actions.kt`, `action/transform/CheckType.kt`,
  `action/supplier/Actions.kt`, `action/game/ActionPlayer.kt`,
  `action/game/compat/ActionPlaceholder.kt`, `KetherConcurrent.kt`.
- Changes: Java 8 translation, explicit host services, read-only player properties,
  existing Klib equality alias retained, strict missing-argument validation and
  executor-bound continuations. No TabooLib or Kotlin runtime dependency.
- Numeric coercion uses the Sponge Coerce algorithm and license identified above.
