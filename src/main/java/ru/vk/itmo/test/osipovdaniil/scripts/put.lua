id = 0

function request()
    curId = id
    id = id + 1
    path = "/v0/entity?id=key" .. curId
    headers = {}
    headers["Host"] = "localhost:808" .. string.char((math.random(1, 4) - 1) * 10)
    value = "value" .. curId
    return wrk.format("PUT", path, headers, value)
end
