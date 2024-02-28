counter = 0

function request()
    counter = counter + 1
    local headers = {}
    headers["Host"] = "localhost:8080"
    local key = "key" .. counter
    local value = "value$" .. counter
    return wrk.format("PUT", "/v0/entity?id=" .. key, headers, value)
end