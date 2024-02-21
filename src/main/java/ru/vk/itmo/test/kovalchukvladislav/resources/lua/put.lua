counter = 0
headers = {}
headers["Host"] = "localhost:8080"

request = function()
    counter = counter + 1
    path = "/v0/entity?id=k" .. counter
    body = "v" .. counter
    return wrk.format("PUT", path, headers, body)
end
