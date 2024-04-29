id = 0

function request()
    curId = id
    id = id + 1
    path = "/v0/entity?id=key" .. curId .. "&ack=2&from=3"
    headers = {}
    headers["Host"] = "localhost:808" .. string.char(math.random(1, 4))
    return wrk.format("GET", path, headers)
end
