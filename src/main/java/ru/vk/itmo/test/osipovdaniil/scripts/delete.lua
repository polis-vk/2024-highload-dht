id = 0

function request()
    curId = id
    id = id + 1
    path = "/v0/entity?id=key" .. curId
    headers = {}
    headers["Host"] = "localhost:8080"
    return wrk.format("DELETE", path, headers)
end
