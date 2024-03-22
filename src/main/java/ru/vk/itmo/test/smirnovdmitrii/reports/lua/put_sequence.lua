counter = 100000000

function request()
    headers = {}
    headers["Host"] = "localhost:8080"
    key = counter
    counter = counter + 1
    value = "value" .. tostring(key) .. tostring(key) .. tostring(key)
    return wrk.format("PUT", "/v0/entity?id=key" .. tostring(key), headers, value)
end