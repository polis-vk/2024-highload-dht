counter = 0

request = function()
    headers = {}
    headers["Host"] = "localhost:8080"
    headers["Content-Type"] = "application/json"
    path = "/v0/entity?id=k" .. counter
    counter = counter + 1
    wrk.body = "v" .. counter
    return wrk.format("PUT", path, headers)
end
