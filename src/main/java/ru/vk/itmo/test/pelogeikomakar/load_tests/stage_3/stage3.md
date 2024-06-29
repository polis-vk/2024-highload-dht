# stage 3
Используется для `wrk2`: 1 поток, 120 подключений, 30 секунд; порог для `flush` ~1Мб; 3 кластера.

## PUT

Максимальная задержка ~10ms получилась при ~10 000 RPS. 
На CPU flame graph видно "плато" у метода `DaoHttpServer.upsertDaoMethod`. 
После изменения количетсва потоков в ThreadPoolExecutor с 20 на 6 (6 * 3 = 18 ~= 20) latency изменилась при 12 000 RPS: 
```text
  Latency Distribution (HdrHistogram - Recorded Latency)
 50.000%    2.42ms    ->    2.06ms
 75.000%    3.22ms    ->    2.61ms
 90.000%    4.08ms    ->    3.15ms
 99.000%    5.99ms    ->    4.37ms
 99.900%    8.26ms    ->    7.39ms
 99.990%   11.14ms    ->    8.77ms
 99.999%   13.02ms    ->    9.35ms
100.000%   14.60ms    ->    9.38ms
```

Далее была убрана блокировка для доступа к HTTP Client'а, 
убрано создание неиспользуемых объектов.

Задержка меняется при RPS

12 000 -> 20 000 -> 30 000 -> 35 000 -> 40 000 -> 45 000 -> 50 000 -> 60 000:
```text
  Latency Distribution (HdrHistogram - Recorded Latency)
 50.000%    2.00ms -> 1.34ms ->  1.38ms ->  1.36ms ->  1.40ms ->  1.42ms ->  1.40ms -> 1.42ms
 75.000%    2.59ms -> 1.87ms ->  1.90ms ->  1.88ms ->  1.95ms ->  1.97ms ->  1.97ms -> 2.01ms
 90.000%    3.23ms -> 2.39ms ->  2.38ms ->  2.39ms ->  2.62ms ->  2.57ms ->  2.66ms -> 2.94ms
 99.000%    4.43ms -> 3.71ms ->  4.81ms ->  5.93ms ->  6.12ms ->  7.89ms ->  8.22ms -> 9.22ms
 99.900%    6.60ms -> 6.92ms ->  7.23ms ->  8.61ms ->  9.37ms -> 12.97ms -> 13.05ms -> 13.68ms
 99.990%    7.92ms -> 8.48ms ->  9.53ms -> 10.89ms -> 12.35ms -> 15.16ms -> 15.86ms -> 17.28ms
 99.999%    8.53ms -> 9.18ms -> 12.03ms -> 11.87ms -> 13.77ms -> 18.75ms -> 18.80ms -> 19.55ms
100.000%    8.94ms -> 9.70ms -> 12.85ms -> 13.51ms -> 15.41ms -> 20.51ms -> 19.28ms -> 21.22ms
```

Для RPS 70 000, 80 000, 90 000 и 100 000 видно, что задержка начала увеличиваться быстрее, 
так же разница между 90 и 99 перцентилями увиеличилась:
```text
  Latency Distribution (HdrHistogram - Recorded Latency)
 50.000%    1.41ms ->  1.37ms ->  1.39ms ->  1.60ms
 75.000%    2.04ms ->  2.05ms ->  2.10ms ->  2.96ms
 90.000%    3.63ms ->  4.35ms ->  4.85ms ->  9.35ms
 99.000%   13.99ms -> 12.47ms -> 16.05ms -> 22.74ms
 99.900%   20.22ms -> 18.00ms -> 22.61ms -> 33.57ms
 99.990%   22.64ms -> 28.22ms -> 25.14ms -> 42.56ms
 99.999%   26.06ms -> 31.60ms -> 29.18ms -> 46.91ms
100.000%   27.31ms -> 32.24ms -> 30.32ms -> 48.67ms
```

При увеличении времени нагрузки до 1 минуты при 50 000, 60 000 RPS:
```text
  Latency Distribution (HdrHistogram - Recorded Latency)
 50.000%    1.41ms ->  1.43ms
 75.000%    1.97ms ->  2.02ms
 90.000%    2.61ms ->  2.86ms
 99.000%    7.10ms ->  9.52ms
 99.900%   13.40ms -> 15.85ms
 99.990%   18.32ms -> 19.06ms
 99.999%   22.91ms -> 22.30ms
100.000%   24.48ms -> 26.85ms
```
При 70 000 RPS в течении минуты уже не каждый раз получается задержка меньше 30ms. 
Таким образом, было выбрано 60 000 RPS.
```shell
wrk -d 60 -t 1 -c 120 -R 60000 -L -s ./lua/put_req.lua http://127.0.0.1:8080
```


###  async-profiler

```shell
wrk -d 60 -t 1 -c 120 -R 50000 -L -s ./lua/put_req.lua http://127.0.0.1:8080
```
```shell
cd /opt/async-profiler-3.0-linux-x64
export PATH=$PATH:/opt/async-profiler-3.0-linux-x64
export JAVA_HOME=/home/user/.jdks/openjdk-21.0.1
export PATH=$JAVA_HOME/bin:$PATH

source ~/.bashrc

sudo sysctl kernel.perf_event_paranoid=1
sudo sysctl kernel.kptr_restrict=0

jps -m
```

```shell
STAGE=3
METHOD_NAME=PUT
TITLE_PREF=${METHOD_NAME}-t1c120rps-50000
DATA_DIR="/media/user/DATA/projects/gitproj/DWS-ITMO-2023/sem_2/2024-highload-dht/src/main/java/ru/vk/itmo/test/pelogeikomakar/load_tests/stage_${STAGE}/data"
JFR_FILENAME="profile${STAGE}-${TITLE_PREF}.jfr"


./bin/asprof --title ${TITLE_PREF} -e cpu,alloc,lock -d 30 -f ${DATA_DIR}/${JFR_FILENAME} MainServ

java -cp lib/converter.jar jfr2flame --alloc --title ${TITLE_PREF}-ALLOC ${DATA_DIR}/${JFR_FILENAME} > "${DATA_DIR}/profile${STAGE}-${TITLE_PREF}-alloc.html"
java -cp lib/converter.jar jfr2flame --lock --title ${TITLE_PREF}-LOCK ${DATA_DIR}/${JFR_FILENAME} > "${DATA_DIR}/profile${STAGE}-${TITLE_PREF}-lock.html"
java -cp lib/converter.jar jfr2flame --title ${TITLE_PREF}-CPU ${DATA_DIR}/${JFR_FILENAME} > "${DATA_DIR}/profile${STAGE}-${TITLE_PREF}-cpu.html"
```

### Flame graphs
[CPU-PUT flame graph](data/profile3-PUT-t1c120rps-50000-cpu.html)

[Allocation-PUT flame graph](data/profile3-PUT-t1c120rps-50000-alloc.html)

[Lock-PUT flame graph](data/profile3-PUT-t1c120rps-50000-lock.html)


  <br>

---------------------------------------------------
<br>

## GET
Сначала были вставлены ~3 710 000 записей.

Используется скрипт с рандомной генерацией ключа (get_random_req.lua).

RPS 10 000 -> 20 000 -> 30 000 -> 40 000 -> 50 000:
```text
  Latency Distribution (HdrHistogram - Recorded Latency)
 50.000%    1.42ms -> 1.48ms -> 1.55ms ->  1.60ms -> 1.77ms
 75.000%    1.94ms -> 2.07ms -> 2.14ms ->  2.28ms -> 2.66ms
 90.000%    2.51ms -> 2.61ms -> 2.73ms ->  3.02ms -> 4.20ms
 99.000%    3.28ms -> 3.55ms -> 4.05ms ->  5.88ms -> 9.83ms
 99.900%    3.92ms -> 4.64ms -> 5.45ms ->  8.67ms -> 14.10ms
 99.990%    5.52ms -> 6.28ms -> 6.54ms -> 12.49ms -> 18.58ms
 99.999%    6.41ms -> 6.86ms -> 8.15ms -> 14.87ms -> 21.18ms
100.000%    6.53ms -> 7.14ms -> 8.65ms -> 15.75ms -> 22.24ms
```

При увеличении нагрузки до минуты RPS 30 000 -> 40 000 -> 50 000:
```text
 50.000%    1.55ms ->  1.59ms ->  1.80ms
 75.000%    2.12ms ->  2.23ms ->  2.74ms
 90.000%    2.71ms ->  2.91ms ->  4.35ms
 99.000%    4.12ms ->  5.66ms -> 10.01ms
 99.900%    6.01ms ->  7.97ms -> 14.18ms
 99.990%    7.53ms ->  9.60ms -> 17.09ms
 99.999%    8.89ms -> 10.96ms -> 19.38ms
100.000%   10.59ms -> 11.98ms -> 22.35ms
```

Поэтому было выбрано 40 000 RPS.

###  async-profiler

```shell
wrk -d 60 -t 1 -c 120 -R 35000 -L -s ./lua/get_random_req.lua http://127.0.0.1:8080
```
```shell
STAGE=3
METHOD_NAME=GET
TITLE_PREF=${METHOD_NAME}-t1c120rps-35000
DATA_DIR="/media/user/DATA/projects/gitproj/DWS-ITMO-2023/sem_2/2024-highload-dht/src/main/java/ru/vk/itmo/test/pelogeikomakar/load_tests/stage_${STAGE}/data"
JFR_FILENAME="profile${STAGE}-${TITLE_PREF}.jfr"


./bin/asprof --title ${TITLE_PREF} -e cpu,alloc,lock -d 30 -f ${DATA_DIR}/${JFR_FILENAME} MainServ

java -cp lib/converter.jar jfr2flame --alloc --title ${TITLE_PREF}-ALLOC ${DATA_DIR}/${JFR_FILENAME} > "${DATA_DIR}/profile${STAGE}-${TITLE_PREF}-alloc.html"
java -cp lib/converter.jar jfr2flame --lock --title ${TITLE_PREF}-LOCK ${DATA_DIR}/${JFR_FILENAME} > "${DATA_DIR}/profile${STAGE}-${TITLE_PREF}-lock.html"
java -cp lib/converter.jar jfr2flame --title ${TITLE_PREF}-CPU ${DATA_DIR}/${JFR_FILENAME} > "${DATA_DIR}/profile${STAGE}-${TITLE_PREF}-cpu.html"
```

### Flame graphs

[CPU-GET flame graph](data/profile3-GET-t1c120rps-35000-cpu.html)

[Allocation-GET flame graph](data/profile3-GET-t1c120rps-35000-alloc.html)

[Lock-GET flame graph](data/profile3-GET-t1c120rps-35000-lock.html)

<br>

---------------------------------------------------
<br>

## Вывод

- у GET и PUT запросов примерно одинаковое количество процентов занимают аллокации,
связанные с `HttpClient`.
- Точка разладки сместилась вправо для GET и PUT запросов, это связано с тем,
что дополнительный HTTP запрос требует много ресурсов.
- Я ожидал, что на cpu flame graph у PUT запросов будет больше семплов, 
связанных с отправкой запроса на другую ноду, так как надо писать в сокет тело запроса; 
действительно отправка запросов на другую ноду у PUT заняла больше процентов, чем у GET, 
но разница весьма мала (тело запроса тоже).
- Блокировки у GET и PUT запросов тоже примерно одинаковы.


<br>

---------------------------------------------------
<br>

## Исправление для stage 2
Используется для `wrk2`: 1 поток, 120 подключений, 30 секунд; порог для `flush` ~1Мб.

### Найдем точку разладки для PUT запросов
При RPS = 125 000 получены перцентили:
```text
  Latency Distribution (HdrHistogram - Recorded Latency)
 50.000%    1.27ms
 75.000%    1.65ms
 90.000%    2.00ms
 99.000%    2.91ms
 99.900%    7.78ms
 99.990%    9.21ms
 99.999%   10.13ms
100.000%   10.57ms
```

При RPS = 130 000, если попробовать нагрузить несколько раз,
то можно 1 - 2 раза получить задержку меньше 10ms на 100 перцентили.
При RPS = 140 000 видно, что сервер уже не справляется:
```text
  Latency Distribution (HdrHistogram - Recorded Latency)
 50.000%    1.40ms
 75.000%    1.88ms
 90.000%    2.56ms
 99.000%   53.15ms
 99.900%   64.10ms
 99.990%   75.46ms
 99.999%   78.14ms
100.000%   78.46ms
```
Таким образом, было выбрано значение RPS = 125 000:
```shell
wrk -d 30 -t 1 -c 120 -R 125000 -L -s ./lua/put_req.lua http://127.0.0.1:8080
```

### Найдем точку разладки для GET запросов
Сначала были вставлены ~3 710 000 записей.

Используется скрипт с рандомной генерацией ключа (`get_random_req.lua`), так как в stage 3 будет использоваться именно этот способ нагрузки.

При RPS = 40 000 получены перцентили:
```text
  Latency Distribution (HdrHistogram - Recorded Latency)
 50.000%    1.53ms
 75.000%    2.12ms
 90.000%    2.75ms
 99.000%    4.72ms
 99.900%    7.31ms
 99.990%    8.85ms
 99.999%    9.81ms
100.000%   10.44ms
```

При 60 000 RPS уже начинается быстрый рост задержки:
```text
  Latency Distribution (HdrHistogram - Recorded Latency)
 50.000%    2.03ms
 75.000%    3.68ms
 90.000%    5.68ms
 99.000%   12.91ms
 99.900%   17.52ms
 99.990%   21.26ms
 99.999%   25.10ms
100.000%   26.22ms
```
Таким образом, для GET запросов было выбрано 40 000 RPS
```shell
wrk -d 30 -t 1 -c 120 -R 40000 -L -s ./lua/get_random_req.lua http://127.0.0.1:8080
```