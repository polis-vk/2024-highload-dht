counter = 0

request = function()
    headers = {}
    headers["Host"] = "localhost:8080"
    path = "/v0/entity?id=k" .. counter
    counter = counter + 1
    return wrk.format("GET", path, headers)
end
