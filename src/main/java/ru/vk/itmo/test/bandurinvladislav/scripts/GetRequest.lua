counter = 0

function request()
    path = "/v0/entity?id=K" .. counter
    counter = counter + 1
    headers = {}
    headers["Host"] = "localhost:8080"
    return wrk.format("GET", path, headers)
end
