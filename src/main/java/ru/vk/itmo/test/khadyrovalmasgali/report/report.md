## Отчёт Stage 1
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

Видно, что в методе GET много ресурсов процессора уходит
на то, чтобы достать значение из SSTable бин поиском.
Можно использовать различные оптимизации, например, Bloom Filter.

В PUT большая часть ресурсов уходит на обработку HTTP реквестов,
лишь малая часть на работу Dao.
