# Отчёт stage1

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
В начале работы я заполнил хранилище БД на 2 Гб с помощью Put запросов

### Получения метрик для Get запроса

#### Запуск wrk2:
```
wrk -d 240 -t 1 -c 1 -R 3000 -L -s /home/pavel/IdeaProjects/2024-highload-dht/src/main/java/ru/vk/itmo/test/pavelemelyanov/script/lua/stage1/get.lua http://localhost:8080
```

![get-3000rpc-240s.png](..%2F..%2Fstatistic%2Fwrk%2Fstage1%2Fget-3000rpc-240s.png)

На графике наблюдается рост задержки возле 99.9%

![get-3000rpc-240s.png](..%2F..%2Fstatistic%2Fprofiler%2Fstage1%2Falloc%2Fget-3000rpc-240s.png)

Большая часть аллокаций происходит при конвертации параметров запроса, из String в MemorySegment, а также используется при отправке ответа на HTTP запрос и при парсинге запроса

![get-3000rpc-240s.png](..%2F..%2Fstatistic%2Fprofiler%2Fstage1%2Fcpu%2Fget-3000rpc-240s.png)
Большая часть cpu расходуется на поиск ключа в БД

### Получения метрик для Put запроса

#### Запуск wrk2:
```
wrk -d 240 -t 1 -c 1 -R 3000 -L -s /home/pavel/IdeaProjects/2024-highload-dht/src/main/java/ru/vk/itmo/test/pavelemelyanov/script/lua/stage1/put.lua http://localhost:8080
```
![put-3000rpc-240s.png](..%2F..%2Fstatistic%2Fwrk%2Fstage1%2Fput-3000rpc-240s.png)

На графике наблюдается рост задержки возле 99.99%

![put-3000rpc-240s.png](..%2F..%2Fstatistic%2Fprofiler%2Fstage1%2Falloc%2Fput-3000rpc-240s.png)

Большая часть аллокаций происходит при конвертации параметров запроса, из String в MemorySegment, а также используется при отправке ответа на HTTP запрос и при парсинге запроса

![put-3000rpc-240s.png](..%2F..%2Fstatistic%2Fprofiler%2Fstage1%2Fcpu%2Fput-3000rpc-240s.png)

Большая часть cpu потребовалось для записи ответа на HTTP запрос и примерно 11% было выделено для записи данных в БД. Также 16% было выделено на ожидание селектора
