# websocketchat-redis

A demo of the Play! Framework's WebSocket-based chat app using Redis as a message server.


## Install to Heroku

    git clone https://github.com/shunjikonishi/websocketchat-redis.git
    cd websocketchat-redis
    heroku create
    heroku labs:enable websockets
    heroku addons:add rediscloud
    git push heroku
    heroku open
