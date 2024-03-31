# Отчёт Stage 1
Опытным путём было выяснено, что dao становится плохо уже на 11к RPS,
поэтому тестирование проводилось на 10k RPS.

[lua get запросы](./scripts/get.lua)

[lua put запросы](./scripts/put.lua)

[wrk2 get output](./wrk/get.txt)

[wrk2 put output](./wrk/put.txt)

[get cpu profile](./asprof/get_cpu.html)

[put cpu profile](./asprof/put_cpu.html)

[get alloc profile](./asprof/get_alloc.html)

[put alloc profile](./asprof/put_alloc.html)

## Выводы
Видно, что в методе GET много ресурсов процессора уходит
на то, чтобы достать значение из SSTable бин поиском.
Можно использовать различные оптимизации, например, Bloom Filter.
15% аллокаций уходит на Request.ok - неудивительно, т. к. мы в нем
отсылаем данные. 11% аллокаций уходит на метод stringToMemorySegment,
преобразовывающий ключ в MemorySegment.

В PUT большая часть ресурсов уходит на обработку HTTP реквестов,
лишь малая часть на работу Dao. 10% аллокаций, опять же, на себя забирает метод stringToMemorySegment,
7% уходит на аллокации при преобразовани строки в MemorySegment -
немного, но и строки сами по себе небольшого размера.

