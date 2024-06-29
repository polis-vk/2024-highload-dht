    function request()
        headers = {}
        headers["Host"] = "localhost:8080"
        id = getRandomId()
        path ="/v0/entity?id=" .. id
        return wrk.format("GET", path, headers)
    end

    function getRandomId()
        minId = 1
        maxId = 10000
        return math.random(minId, maxId)
    end

    --wrk -d 30 -t 1 -c 1 -R 3000 -L  -s ./src/main/java/ru/vk/itmo/test/osokindm/wrk_scripts/get.lua http://localhost:8080/v0/entity
