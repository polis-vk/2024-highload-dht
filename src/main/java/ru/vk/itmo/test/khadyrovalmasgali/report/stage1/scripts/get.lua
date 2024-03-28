counter = 0

request = function()
    headers = {}
    headers["Host"] = "localhost:8080"
    counter = counter + 1
    return wrk.format("GET", "/v0/entity?id=" .. counter, headers)
end