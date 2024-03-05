counter = 0

function request()
    headers = {}
    headers["Host"] = "localhost:8080"
    current = counter
    counter = counter + 1
    return wrk.format("GET", "/v0/entity?id=key" .. current, headers)
end