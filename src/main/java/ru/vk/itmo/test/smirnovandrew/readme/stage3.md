# Отчет о тестировании
## Использованные методы
Все методы реализованы так же как и методы в stage1,
как именно они реализованы можно посмотреть либо в отчете
stage1.md или в папке lua

### PUT запросы

```
./wrk -d 120 -t 1 -c 64 -R 30000 -L -s /home/andrew/my-dht/2024-highload-dht/src/main/java/ru/vk/itmo/test/smirnovandrew/lua/put.lua http://localhost:8080
 ```
```dtd
./profiler.sh --fdtransfer -d 120 -e cpu -f put_cpu_stage3.html jps
```

Анализ CPU:
![put_cpu.png](stage3%2Fput_cpu.png)
Наибольшая нагрузка приходится на парсинг запросов, а также на
выбор кластера и ожидание ответа от http клиента


Анализ ALLOC:
![put_alloc.png](stage3%2Fput_alloc.png)
Большее количество памяти так же, как и в предыдущих частях
тратится на конвертацию MemorySegment в byte[] и обратно


Анализ LOCK:
![put_lock.png](stage3%2Fput_lock.png)


### GET запросы

```
./wrk -d 120 -t 1 -c 64 -R 30000 -L -s /home/andrew/my-dht/2024-highload-dht/src/main/java/ru/vk/itmo/test/smirnovandrew/lua/get.lua http://localhost:8080
 ```
```dtd
./profiler.sh --fdtransfer -d 120 -e cpu -f get_cpu_stage3.html jps
```

Анализ CPU:
![get_cpu.png](stage3%2Fget_cpu.png)
Наибольшая нагрузка приходится на парсинг запросов, а также на
выбор кластера и ожидание ответа от http клиента

Анализ ALLOC:
![get_alloc.png](stage3%2Fget_alloc.png)
Большее количество памяти так же, как и в предыдущих частях
тратится на конвертацию MemorySegment в byte[] и обратно


Анализ LOCK:
![get_lock.png](stage3%2Fget_lock.png)
В основном lock берется на HttpSession, а также на ThreadPoolExecutor


### Сравнение с предыдущей реализацией
![compare.png](stage3%2Fcompare.png)
Как видно из графика сравнения получается так, что предыдущая часть
оказывается эффективнее, похоже, что это происходит из-за того, что
долго выбирается кластер

