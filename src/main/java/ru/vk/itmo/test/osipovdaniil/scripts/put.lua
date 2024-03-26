id = 0

function request()
    curId = id
    id = id + 1
    path = "/v0/entity?id=key" .. curId
    headers = {}
    headers["Host"] = "localhost:8080"
    value = "value" .. curId
    return wrk.format("PUT", path, headers, value)
end
