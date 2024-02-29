id = 0

function request()
    id = id + 1
    path = "/v0/entity?id=" .. id
    headers = {}
    headers["Host"] = "localhost:8080"
    return wrk.format("GET", path, headers)
end
