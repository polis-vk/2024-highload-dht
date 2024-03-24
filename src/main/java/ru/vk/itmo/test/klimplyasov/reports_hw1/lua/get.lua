id = 0
function request()
    path = "/v0/entity?id=" .. id
    headers = {}
    headers["Host"] = "localhost:8080"
    id = id + 1
    return wrk.format("GET", path, headers)
end