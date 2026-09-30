# Match Mode (Forge 1.20.1)

Сборка: положи в папку проекта gradle wrapper из любого Forge MDK 1.20.1
(файлы gradlew, gradlew.bat и папку gradle/), затем `./gradlew build`.
Нужна JDK 17. Готовый мод: build/libs/matchmode-1.0.0.jar (кладётся в mods сервера).

Команды:
  /match center                  центр зоны = твоя позиция (op)
  /match size <старт> <конец> <сек>   диаметры барьера и время сжатия (op)
  /match ready                   голос за старт (все)
  /match stop                    остановить матч (op)
  /preset save <имя>             сохранить инвентарь как пресет
  /preset list                   список пресетов
  /preset delete <имя>           удалить пресет (op)
