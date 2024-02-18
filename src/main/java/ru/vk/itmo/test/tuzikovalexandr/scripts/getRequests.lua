id = 0
function getRequest()
    id = id + 1
    key = "k" .. id
    path = "/v0/entity?id=" .. key

    headers = { }
    headers["Host"] = "localhost:8080"

    return wrk.format("GET", path, headers, nil)
end