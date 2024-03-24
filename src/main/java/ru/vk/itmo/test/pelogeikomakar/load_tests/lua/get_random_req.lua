math.randomseed(42)
function request()
    headers = {}
    headers["Host"] = "localhost:8080"
    reqPath = "/v0/entity?id=" .. math.random(0, 350000)
    return wrk.format("GET", reqPath, headers)
end