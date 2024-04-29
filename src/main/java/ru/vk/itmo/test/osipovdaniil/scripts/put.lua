id = 0

function request()
    curId = id
    id = id + 1
    path = "/v0/entity?id=key" .. curId .. "&ack=2&from=3"
    headers = {}
    headers["Host"] = "localhost:808" .. string.char(math.random(1, 4))
    value = "value" .. curId
    return wrk.format("PUT", path, headers, value)
end
