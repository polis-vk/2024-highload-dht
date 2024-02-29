# Этап 1. HTTP + storage
## Setup
- WRK
  ```wrk -d 2m -t 1 -c 1 -R {value} -s {put/get}.lua -L http://localhost:8080/v0/entry```
- ASYNC-PROFILE
  ```asprof -d 130 -e cpu,alloc -f ./{name}.jfr ApplicationServer```
- CONVERTER
  ```java -cp lib/converter.jar jfr2flame {--alloc / --state default} ./{name}.jfr ./{name}.html```

See cpu flag issue, fixed from v3.0 async-profiler (https://github.com/async-profiler/async-profiler/issues/740)

## Content table

## PUT Research

### 10 thds rps
```
 50.000%    1.05ms
 75.000%    1.54ms
 90.000%    1.87ms
 99.000%    2.09ms
 99.900%    2.77ms
 99.990%    5.30ms
 99.999%    7.07ms
100.000%    7.46ms
```
По перцентилям аномалий не наблюдается.

ALLOC
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task1/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task1/asprof/put/alloc/png/alloc_p10000rps.png)
99.2% NIO Selector:
put 23% - 370MB. Что вполне ожидаемо для размера сохраняемой строки "meow"*100 ~400мб
парсинг тела запроса 30% - 400Мб
довольно много занимает респонз ~13%. В константу засунуть его не вариант, тк wrk начинает сбрасывать соединения, когда сервер отправляет один и тот же объект ответа для всех запросов.

CPU
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task1/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task1/asprof/put/cpu/png/cpu_p10000rps.png)
92% NIO selector: уходит на то, чтобы распарсить запрос, найти аццептор, сформировать и отправить ответ
5% flusher: writeSegment
< 3% C1 C2 GC

### 20 thds rps
```
 50.000%  640.00us
 75.000%    0.95ms
 90.000%    1.35ms
 99.000%    2.08ms
 99.900%    4.95ms
 99.990%    8.07ms
 99.999%    9.00ms
100.000%    9.02ms
```
Просто наблюдаем небольшую деградацию.
ALLOC
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task1/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task1/asprof/put/alloc/png/alloc_p20000rps.png)
CPU
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task1/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task1/asprof/put/cpu/png/cpu_p20000rps.png)

### 30 thds rps
```
 50.000%  636.00us
 75.000%    0.95ms
 90.000%    1.43ms
 99.000%    4.71ms
 99.900%   15.36ms
 99.990%   22.56ms
 99.999%   23.71ms
100.000%   23.76ms
```
Просто наблюдаем небольшую деградацию.
ALLOC
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task1/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task1/asprof/put/alloc/png/alloc_p30000rps.png)
CPU
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task1/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task1/asprof/put/cpu/png/cpu_p30000rps.png)

### 40 thds rps
```
 50.000%  634.00us
 75.000%    0.95ms
 90.000%    1.48ms
 99.000%    6.98ms
 99.900%   20.85ms
 99.990%   34.43ms
 99.999%   35.33ms
100.000%   35.36ms
```
Удивительная точка, тк был скачок по времени, который далее спал. Хотя все тесты проводились в равных условиях. Предполагаю, что это связано с не самым здоровым диском ~80% / существующими тестовыми записями в хранилище, о которые он мог споткнуться.
ALLOC
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task1/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task1/asprof/put/alloc/png/alloc_p40000rps.png)
CPU
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task1/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task1/asprof/put/cpu/png/cpu_p40000rps.png)

### 50 thds rps
Если пропустить 40к, то просто наблюдаем небольшую деградацию.
```
 50.000%  629.00us
 75.000%    0.94ms
 90.000%    1.45ms
 99.000%    5.10ms
 99.900%   19.10ms
 99.990%   25.98ms
 99.999%   26.42ms
100.000%   26.43ms
```
ALLOC
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task1/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task1/asprof/put/alloc/png/alloc_p50000rps.png)
CPU
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task1/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task1/asprof/put/cpu/png/cpu_p50000rps.png)

### 70 thds rps
Просто наблюдаем небольшую деградацию.
```
 50.000%  629.00us
 75.000%    0.94ms
 90.000%    1.45ms
 99.000%    5.10ms
 99.900%   19.10ms
 99.990%   25.98ms
 99.999%   26.42ms
100.000%   26.43ms
```
ALLOC
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task1/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task1/asprof/put/alloc/png/alloc_p70000rps.png)
CPU
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task1/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task1/asprof/put/cpu/png/cpu_p70000rps.png)

### 100 thds rps
Близки к точке разладки
```
 50.000%  568.00us
 75.000%  843.00us
 90.000%    1.00ms
 99.000%    9.13ms
 99.900%   35.29ms
 99.990%   40.38ms
 99.999%   41.28ms
100.000%   41.38ms
```
ALLOC
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task1/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task1/asprof/put/alloc/png/alloc_p100000rps.png)
CPU
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task1/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task1/asprof/put/cpu/png/cpu_p100000rps.png)

### 110 thds rps - точка разладки
```
 50.000%  691.00us
 75.000%    1.02ms
 90.000%    7.32ms
 99.000%   97.41ms
 99.900%  116.35ms
 99.990%  119.10ms
 99.999%  119.42ms
100.000%  119.49ms
```
ALLOC
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task1/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task1/asprof/put/alloc/png/alloc_p1100000rps.png)
CPU
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task1/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task1/asprof/put/cpu/png/cpu_p110000rps.png)
Выросло время на flusher. Почти 8% на writeSegment.

### 120 thds rps
Тестовая нагрузка за точкой разладки
```
 50.000%    4.35s
 75.000%    5.69s
 90.000%    6.30s
 99.000%    6.68s
 99.900%    6.72s
 99.990%    6.72s
 99.999%    6.72s
100.000%    6.72s
```
ALLOC
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task1/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task1/asprof/put/alloc/png/alloc_p120000rps.png)
CPU
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task1/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task1/asprof/put/cpu/png/cpu_p120000rps.png)

### 150 thds rps
Тестовая нагрузка за точкой разладки
```
 50.000%   16.43s
 75.000%   22.95s
 90.000%   26.87s
 99.000%   29.21s
 99.900%   29.44s
 99.990%   29.47s
 99.999%   29.47s
100.000%   29.47s
```
ALLOC
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task1/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task1/asprof/put/alloc/png/alloc_p150000rps.png)
CPU
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task1/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task1/asprof/put/cpu/png/cpu_p150000rps.png)

### 200 thds rps
Тестовая нагрузка за точкой разладки
```
 50.000%   28.52s
 75.000%   40.30s
 90.000%   47.22s
 99.000%    0.86m
 99.900%    0.86m
 99.990%    0.86m
 99.999%    0.86m
100.000%    0.86m
```
ALLOC
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task1/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task1/asprof/put/alloc/png/alloc_p200000rps.png)
CPU
![put](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task1/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task1/asprof/put/cpu/png/cpu_p200000rps.png)

## GET Research 5.44Gb

### 1 thds rps
```
 50.000%    1.22ms
 75.000%    1.50ms
 90.000%    1.80ms
 99.000%    2.10ms
 99.900%    3.02ms
 99.990%   12.64ms
 99.999%   14.93ms
100.000%   15.05ms
```
ALLOC
![get](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task1/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task1/asprof/get/alloc/png/alloc_p1000rps.png)
Почти 40% на формирования респонза... Мда.

CPU
![get](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task1/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task1/asprof/get/cpu/png/cpu_p1000rps.png)
Почти 100% это операция сравнения memorySegment компоратора.

### 3 thds rps - (2 thds точка разладки)
Тяжко было найти точку разладки точнее, тк прыжок появился слишком резко. Ожидал, что выдержит большую нагрузку. Вероятно связано с больным диском и неплохо так заполненной бд.
```
 50.000%   14.75s
 75.000%   21.38s
 90.000%   25.58s
 99.000%   28.10s
 99.900%   28.34s
 99.990%   28.38s
 99.999%   28.38s
100.000%   28.38s
```
ALLOC
![get](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task1/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task1/asprof/get/alloc/png/alloc_p3000rps.png)
CPU
![get](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task1/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task1/asprof/get/cpu/png/cpu_p3000rps.png)

### 5 thds rps
Чек 50тыс.
```
 50.000%   35.68s
 75.000%   50.50s
 90.000%    0.99m
 99.000%    1.08m
 99.900%    1.09m
 99.990%    1.09m
 99.999%    1.09m
100.000%    1.09m
```
ALLOC
![get](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task1/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task1/asprof/get/alloc/png/alloc_p5000rps.png)
CPU
![get](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task1/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task1/asprof/get/cpu/png/cpu_p5000rps.png)

### 10 thds rps
```
 50.000%   50.56s
 75.000%    1.20m
 90.000%    1.41m
 99.000%    1.53m
 99.900%    1.55m
 99.990%    1.55m
 99.999%    1.55m
100.000%    1.55m
```
ALLOC
![get](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task1/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task1/asprof/get/alloc/png/alloc_p10000rps.png)
CPU
![get](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task1/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task1/asprof/get/cpu/png/cpu_p10000rps.png)

### 20 thds rps

### 30 thds rps

### 50 thds rps
Проще всего было увидеть потенциальную деградацию.
```
 50.000%    1.04m
 75.000%    1.47m
 90.000%    1.74m
 99.000%    1.89m
 99.900%    1.91m
 99.990%    1.91m
 99.999%    1.91m
100.000%    1.91m
```
ALLOC
![get](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task1/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task1/asprof/get/alloc/png/alloc_p50000rps.png)
CPU
![get](https://github.com/NoGe4Ek/2024-highload-dht/blob/feature/task1/src/main/java/ru/vk/itmo/test/timofeevkirill/results/task1/asprof/get/cpu/png/cpu_p50000rps.png)
74% compare
Начинает играть роль расчет длины ключа/значения. ~30%.
