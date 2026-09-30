# Match Mode 1.1.1

Forge-мод для Minecraft **1.20.1**.

## Изменения в 1.1.1
- При смерти **сразу spectator** — меню смерти не показывается
- На экране появляется title **«ты 200»**
- Килл-камера и остальная логика матча сохранены

## Сборка

Требования: **JDK 17**

```bash
./gradlew build
```

Готовый jar: `build/libs/matchmode-1.1.1.jar`

## Команды
- `/match ready` — готовность к матчу
- `/match stop` — остановить матч (op)
- `/match center` — центр зоны (op)
- `/match size <start> <end> <seconds>` — размер барьера (op)
- `/preset save|list|load|delete`

Пресеты хранятся в `config/shooterpresets/`.
