function request()
     id = math.random(1, 500000)
     path = "/v0/entity?id=" .. id
     method = "GET"
     return wrk.format(method, path, body)
end
