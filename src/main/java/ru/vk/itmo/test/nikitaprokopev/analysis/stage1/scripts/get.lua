counter = 0

function request()
    counter = counter + 1
    local headers = {}
    headers["Host"] = "localhost:8080"
    local key = "key" .. counter
    return wrk.format("GET", "/v0/entity?id=" .. key, headers)
end