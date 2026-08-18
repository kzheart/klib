# Kether 黄金语料清单

该黄金语料集是一个刻意保持精简的高频/长尾样本，选自 43 个包含 Kether 运行器或
`KetherShell.eval` 入口点的本地项目根目录。清点命令排除了构建产物，并在 Java/Kotlin
生产源码中搜索 `runKether`、`KetherShell.eval` 和 `KetherShell.evalWithoutContext`。

检测到的项目根目录（43 个）：

1. AreaModule
2. ArkFaithl
3. AsterItems
4. BedwarsGuild
5. BossHunt
6. Commandant
7. ControlPoint
8. Cooking
9. Defeat
10. DragonCoreKether
11. Entrance
12. EquipChecker
13. FishX
14. HeroBrawl
15. IAGather
16. IceEnv
17. MECooking
18. MMOIntensify
19. MeritGuilds
20. MythicDamageTracker
21. PVPRank
22. PlayerGifts
23. PlayerRecorder
24. PlayerReset
25. PrisonCell
26. ReadBook
27. RideX
28. SimpleGather
29. SimpleStall
30. SkinRemover
31. TalentTree
32. TowerDefenseAdditional
33. Waypoint
34. adyeshach
35. item-reforge
36. kmodule
37. planners-v2
38. prometheus-all
39. taboolib
40. taboolib-6.1.2
41. taboolib-6.2.2
42. taboolib-6.2.3-250330
43. zaphkiel-master

## 选取来源依据

- `01-simple-stall-condition.kether` — `SimpleStall/src/main/resources/config.yml`
- `02-fishx-reward.kether` — `FishX/src/main/resources/rewards.yml`
- `03-entrance-command.kether` and `04-entrance-condition.kether` —
  `Entrance/src/main/resources/entrance/魔狼系列.yml`
- `05-read-book-condition.kether` — `ReadBook/src/main/resources/books.yml`
- `06-player-reset-reward.kether` — `PlayerReset/src/main/resources/config.yml`
- 长尾用例覆盖 TabooLib Java `SimpleReader` 中的核心语法原语：行注释、引用/字面量前缀、
  单引号块、三引号块和命名块分隔符。

复制的脚本仅作为测试夹具使用，不会在 Bukkit 上执行，也不包含脚本表达式之外的密钥、
玩家数据、端点或插件配置。
