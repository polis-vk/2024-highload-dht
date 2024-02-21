# Отчет о тестировании
## Использованные методы
### GET
Реализован следующим образом:
```agsl
id = 0

function request()
    id = id + 1
    path = "/v0/entity?id=" .. id
    headers = {}
    headers["Host"] = "localhost:8080"
    return wrk.format("GET", path, headers)
end
```

### DELETE
Реализован следующим образом:
```agsl
id = 0

function request()
    id = id + 1
    path = "/v0/entity?id=" .. id
    headers = {}
    headers["Host"] = "localhost:8080"
    return wrk.format("DELETE", path, headers)
end
```

### PUT
Реализован следующим образом:
```agsl
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
### Testing
Для начала нужно заполнить dao на несколько мегобайт с помощью последовательного
вызова `PUT`, поэтому начнем тестирование с него


### Тест PUT
```agsl
./wrk -d 240 -t 1 -c 1 -R 3000 -L -s /Users/sandrew2003/IdeaProjects/highload/2024-highload-dht/src/main/java/ru/vk/itmo/test/smirnovandrew/lua/put.lua http://localhost:8080
```
результат:


### Тест GET
```agsl
./wrk -d 240 -t 1 -c 1 -R 3000 -L -s /Users/sandrew2003/IdeaProjects/highload/2024-highload-dht/src/main/java/ru/vk/itmo/test/smirnovandrew/lua/get.lua http://localhost:8080
```
