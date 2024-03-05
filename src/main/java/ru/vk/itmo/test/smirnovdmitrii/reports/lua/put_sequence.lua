counter = 0

function request()
    headers = {}
    headers["Host"] = "localhost:8080"
    current = counter
    counter = counter + 1
    return wrk.format("PUT", "/v0/entity?id=key" .. current, headers, "value" .. current)
end