id = 0
function request()
    headers = {}
    headers["Host"] = "localhost:8080"
    id = id + 1
    return wrk.format("GET", "/v0/entity?id=" .. id, headers)
end
