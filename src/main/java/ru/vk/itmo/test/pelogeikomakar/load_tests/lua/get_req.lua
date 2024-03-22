id = 0
function request()
    headers = {}
    headers["Host"] = "localhost:8080"
    reqPath = "/v0/entity?id=" .. id
    id = id + 1
    return wrk.format("GET", reqPath, headers)
end