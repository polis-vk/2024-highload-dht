key = 1;

wrk.method = "PUT"
wrk.body   = "hello"
wrk.headers["Content-Type"] = "Content-Type: text/plain; charset=utf-8"

request = function()
   path = "/v0/entity?id=" .. key
   key = key + 1;
   return wrk.format(nil, path)
end
