# Отчёт stage2

## Lua

### Put запрос
Написан для заполнения БД случайными строками:
```
id = 0

function random_string()
    str = ""
    for i = 1, math.random(2, 100)
    do
        str = str .. string.char(math.random(97, 122))
    end
    return str
end

function request()
    id = id + 1
    path = "/v0/entity?id=" .. id
    headers = {}
    headers["Host"] = "localhost:8080"
    body = random_string()
    return wrk.format("PUT", path, headers, body)
end
```

### Get запрос
Написан для получения данных из БД
```
id = 0

function request()
    id = id + 1
    path = "/v0/entity?id=" .. id
    headers = {}
    headers["Host"] = "localhost:8080"
    return wrk.format("GET", path, headers)
end

```

### Delete запрос
Написан для удаления данных из БД
```
id = 0

function request()
    id = id + 1
    path = "/v0/entity?id=" .. id
    headers = {}
    headers["Host"] = "localhost:8080"
    return wrk.format("DELETE", path, headers)
end
```

## wrk2
### Подготовка
В начале работы я заполнил хранилище БД на 2.3 Гб с помощью Put запросов

Точка разладки достигается при 17000 rps, поэтому буду тестировать на 16000 rps

### Получения метрик для Get запроса

#### Запуск wrk2:
```
wrk -d 120 -t 4 -c 64 -R 16000 -L -s /home/pavel/IdeaProjects/2024-highload-dht/src/main/java/ru/vk/itmo/test/pavelemelyanov/script/lua/stage1/get.lua http://localhost:8080
```

![get-16000rps-120s.png](..%2F..%2Fstatistic%2Fwrk%2Fstage2%2Fget-16000rps-120s.png)

На графике наблюдается рост задержки возле 99.9%, что не критично

[alloc-get.html](..%2F..%2Fstatistic%2Fprofiler%2Fstage2%2Falloc%2Falloc-get.html)

Большая часть аллокаций происходит всё также при конвертации параметров запроса, из String в MemorySegment и при парсинге запроса.
Также появились немного дополнительных аллокаций для работы с Queue

[cpu-get.html](..%2F..%2Fstatistic%2Fprofiler%2Fstage2%2Fcpu%2Fcpu-get.html)

Значительно снизилась нагрузка на селектор потоки за счёт добавления воркеров по обработке запроса, Теперь селекторам надо только пропарсить сам запрос и добавить задачу в очередь для воркеров
однако воркеры тратят время на ожидания задач
Значительное время тратится на работу с базой данных
Часть времени тратим для записи ответа на HTTP запрос

### Получения метрик для Put запроса

#### Запуск wrk2:
```
wrk -d 120 -t 4 -c 64 -R 16000 -L -s /home/pavel/IdeaProjects/2024-highload-dht/src/main/java/ru/vk/itmo/test/pavelemelyanov/script/lua/stage1/put.lua http://localhost:8080
```

![put-16000rps-120s.png](..%2F..%2Fstatistic%2Fwrk%2Fstage2%2Fput-16000rps-120s.png)

На графике задержка постепенно растёт от 90%


![alloc-put.png](..%2F..%2Fstatistic%2Fprofiler%2Fstage2%2Falloc%2Falloc-put.png)

Большая часть аллокаций происходит всё также при конвертации параметров запроса, из String в MemorySegment и при парсинге запроса. 
Также появились немного дополнительных аллокаций для работы с Queue

![cpu-put.png](..%2F..%2Fstatistic%2Fprofiler%2Fstage2%2Fcpu%2Fcpu-put.png)

Значительно снизилась нагрузка на селектор потоки за счёт добавления воркеров по обработке запроса, Теперь селекторам надо только пропарсить сам запрос и добавить задачу в очередь для воркеров
Однако мы тратим время на то чтобы заблокировать и разблокировать Queue
Значительное время тратится на работу с базой данных
Часть времени тратим для записи ответа на HTTP запрос

### Итог

Перешёл на более слабо железо после выполнения stage1, потому что Ubuntu не запускается.
Несмотря на более слабо железо точка разладки выросла с 4000 до 17000 rps.

Если перейти на LinkedBlockingQueue, то может возникнуть ситуация, когда у нас будет не хватать памяти на хранения невыолненных тасок

### Мысли
1. Можно изменить размер очереди:
   * если уменьшить то мы не будем тратить память на хранение лишних (слишком старых) запросов
   * если увеличивать, то мы будем хранить больше тасок, которые постепенно будут выполняться воркерами
2. Часть, связанную с чтением запроса и записью ответов, мы не можем исправить. Только если ресурсов дадим больше