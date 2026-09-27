idf_component_register(
    SRCS
        "hello_world_main.c"
        "oled_display.c"

    PRIV_REQUIRES
        spi_flash

    INCLUDE_DIRS
        "."

    REQUIRES
        bt
        nvs_flash
        esp_driver_uart
        esp_driver_gpio
        esp_driver_i2c
)